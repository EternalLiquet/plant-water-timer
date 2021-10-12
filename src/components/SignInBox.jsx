import { Box } from "@material-ui/core";
import React from "react";
import SignIn from "./SignIn";

export default function SignInBox(props) {
  return (
    <Box
      display="flex"
      justifyContent="center"
      alignItems="center"
      minHeight="100%"
      bgcolor="rgb(18, 18, 18, 0.5)"
    >
      <SignIn auth={props.auth} firebase={props.firebase} />
    </Box>
  );
}
