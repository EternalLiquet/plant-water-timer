import React from 'react';
import { AppBar, Box, Button, Container, createTheme, CssBaseline, Grid, makeStyles, TextField, Toolbar, Typography } from '@material-ui/core';
import { ThemeProvider } from '@material-ui/styles';

import firebase from 'firebase/compat/app';
import 'firebase/compat/firestore';
import 'firebase/compat/auth';

import { useAuthState } from 'react-firebase-hooks/auth';
import { useCollectionData } from 'react-firebase-hooks/firestore';
import SignIn from './components/SignIn';
import SignInBox from './components/SignInBox';
import SignOut from './components/SignOut';
import AccountSignedIn from './components/AccountSignedIn';

const darkTheme = createTheme({
  palette: {
    type: 'dark',
  }
});

firebase.initializeApp({
  apiKey: "AIzaSyAtQtSFrmXIylCOktouGw2YsnKwGgv6U3k",
  authDomain: "plant0.firebaseapp.com",
  projectId: "plant0",
  storageBucket: "plant0.appspot.com",
  messagingSenderId: "438322523913",
  appId: "1:438322523913:web:8258be948ab81d86616dd8"
});

const useStyles = makeStyles(() => ({
  mainLayoutGrid: {
    minHeight: "95vh",
    width: "100vw",
    maxWidth: "100%"
  },
  verticalMarginGrid: {
    minHeight: "10%",
    maxHeight: "10%"
  },
  contentGrid: {
    minHeight: "80%"
  }
}));

const auth = firebase.auth();
const firestore = firebase.firestore();

function App() {
  const classes = useStyles();
  const [user] = useAuthState(auth);
  const plantsRef = firestore.collection("plants");
  const query = plantsRef.where("uid", "==", user ? auth.currentUser.uid : '')
  const [plants] = useCollectionData(query, { idField: 'id' });

  return (
    <ThemeProvider theme={darkTheme}>
      <CssBaseline />
      <div class="page-bg" />
      <Box sx={{ flexGrow: 1 }}>
        <AppBar position="static" style={{ background: "#127A42"}}>
          <Toolbar>
            <Typography>Plant Water Reminder</Typography>
            <div style={{ flexGrow: 1}}/>
            {user ? <AccountSignedIn auth={auth} firebase={firebase}/> : <SignIn auth={auth} firebase={firebase}/>}
          </Toolbar>
        </AppBar>
      </Box>
      <Grid container className={classes.mainLayoutGrid}>
        <Grid xs={12} className={classes.verticalMarginGrid}/>
        <Grid xs={2} className={classes.contentGrid}/>
        <Grid xs={8} className={classes.contentGrid}>
          {plants && plants.map(plant =>
            <Container key={plant.id}>
              <Typography>Plant Name: {plant.plantName}</Typography>
              <Typography>Last Watered Date: {plant.lastWatering.toDate().toString()}</Typography>
              <Typography>Next Watering Date: {plant.nextWatering.toDate().toString()}</Typography>
            </Container>)}
        </Grid>
        <Grid xs={2} className={classes.contentGrid}/>
        <Grid xs={12} className={classes.verticalMarginGrid}/>
      </Grid>
    </ThemeProvider>
  );
}

export default App;
