import React from 'react';
import { Avatar, Typography } from '@material-ui/core';
import SignOut from './SignOut';

export default function AccountSignedIn(props) {

    const { displayName, photoURL } = props.auth.currentUser;
    
    console.log(props.auth.currentUser)

    return (
        <React.Fragment>
            <Typography>{displayName}</Typography>
            <Avatar alt="Current User Avatar" src={photoURL} style={{ margin: "5px" }}/>
            <SignOut auth={props.auth} firebase={props.firebase} />
        </React.Fragment>
      );
}